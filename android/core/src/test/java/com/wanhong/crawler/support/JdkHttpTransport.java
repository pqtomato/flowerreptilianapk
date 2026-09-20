package com.wanhong.crawler.support;

import com.wanhong.crawler.config.CrawlerSettings;
import com.wanhong.crawler.http.FetchResponse;
import com.wanhong.crawler.http.HttpTransport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 仅用于测试的 JVM 传输实现（java.net.http）。
 *
 * <p>它让内核可以在电脑上直接打真实站点 —— 也就是说，
 * <b>同一套采集逻辑既能在安卓上用 OkHttp 跑，也能在 JVM 上用这套实现验证</b>。
 * 便携性是内核抽象出 {@link HttpTransport} 的直接收益。
 */
public class JdkHttpTransport implements HttpTransport {

    static {
        // JDK 的 HttpClient 把"主机名校验"做成了 JVM 级静态开关，
        // 且必须在任何 HttpClient 实例创建之前设置 —— 政务站点的证书主机名常常对不上
        // （wyxmzx 的证书里根本没有该域名），不关掉这里会直接抛
        // "No subject alternative DNS name matching"。
        //
        // 注意：安卓侧用的是 OkHttp，可以【按域名】放宽（hostnameVerifier），
        // 比这个全局开关安全；这里只是为了让同一份内核能在电脑上跑联调验证。
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
    }

    private final CrawlerSettings settings;
    private final HttpClient client;
    private final CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);

    public JdkHttpTransport(CrawlerSettings settings) {
        this.settings = settings;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(settings.request.connectTimeoutSeconds))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .cookieHandler(cookieManager)
                .version(HttpClient.Version.HTTP_1_1)
                .sslContext(insecureContext())
                .build();
    }

    private static SSLContext insecureContext() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, new SecureRandom());
            return context;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public FetchResponse send(String method, String url, Map<String, String> headers, byte[] body)
            throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(encode(url)))
                .timeout(Duration.ofSeconds(settings.request.timeoutSeconds));
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                builder.header(entry.getKey(), entry.getValue());
            }
        }
        if (body == null) {
            builder.GET();
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        }

        try {
            HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new FetchResponse(response.statusCode(), decompress(response),
                    response.headers().firstValue("Content-Type").orElse(""),
                    response.uri().toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("被中断", e);
        }
    }

    @Override
    public void clearCookies(String host) {
        for (java.net.HttpCookie cookie : new java.util.ArrayList<>(cookieManager.getCookieStore().getCookies())) {
            String domain = cookie.getDomain();
            if (domain != null && (host.equalsIgnoreCase(domain) || host.endsWith(domain))) {
                cookieManager.getCookieStore().remove(null, cookie);
            }
        }
    }

    @Override
    public String name() {
        return "JDK-HttpClient";
    }

    private byte[] decompress(HttpResponse<byte[]> response) throws IOException {
        byte[] raw = response.body();
        String encoding = response.headers().firstValue("Content-Encoding").orElse("").toLowerCase();
        if (encoding.contains("gzip")) {
            return readAll(new GZIPInputStream(new ByteArrayInputStream(raw)));
        }
        if (encoding.contains("deflate")) {
            return readAll(new InflaterInputStream(new ByteArrayInputStream(raw)));
        }
        return raw;
    }

    private byte[] readAll(InputStream in) throws IOException {
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    /** 中文 / 空格地址转义 */
    public static String encode(String url) {
        StringBuilder sb = new StringBuilder(url.length() + 32);
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c > 127 || c == ' ' || c == '|') {
                for (byte b : String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                    sb.append('%');
                    String hex = Integer.toHexString(b & 0xFF).toUpperCase();
                    if (hex.length() == 1) {
                        sb.append('0');
                    }
                    sb.append(hex);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
