package com.wanhong.crawler.http;

import com.wanhong.crawler.config.CrawlerProperties;
import com.wanhong.crawler.config.HostnameVerificationInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 统一 HTTP 抓取器。
 *
 * <p>要点：
 * <ul>
 *   <li>使用 JDK 内置 HttpClient（无第三方依赖），强制 HTTP/1.1 以兼容政府站群；</li>
 *   <li>绝不发送 {@code Origin} 头 —— 安庆阳光采购平台的 WAF 会把带 Origin 的
 *       接口请求判定为「疑似跨站请求伪造」并返回 503；</li>
 *   <li>不发送 JDK 受限头（Connection / Content-Length / Host / Expect / Upgrade）；</li>
 *   <li>随机 UA + 同域名最小请求间隔，礼貌抓取；</li>
 *   <li>网络异常 / 5xx / 429 / 403 自动退避重试；</li>
 *   <li>手动解压 gzip / deflate。</li>
 * </ul>
 */
@Component
public class HttpFetcher {

    private static final Logger log = LoggerFactory.getLogger(HttpFetcher.class);

    private static final String DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private final CrawlerProperties properties;
    private final HttpClient client;
    /** 证书过期/自签名站点专用的"跳过校验"客户端，按需创建 */
    private volatile HttpClient insecureClient;
    private final Random random = new Random();

    /** 每个域名的串行锁，保证同域名请求按最小间隔顺序发出 */
    private final Map<String, Object> hostLocks = new ConcurrentHashMap<>();
    private final Map<String, Long> hostLastRequestAt = new ConcurrentHashMap<>();
    /** 运行时发现证书链有问题的域名，后续请求直接走宽松客户端 */
    private final Set<String> learnedInsecureHosts = ConcurrentHashMap.newKeySet();
    /**
     * 全站共享的 Cookie 容器：政务站点的 WAF 常在首次访问时下发 cookie
     * （参考上一版系统记录的安庆 __jsluid_s），带着它后续请求更稳。
     */
    private final CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    /** 已预热过的域名（每域名一次） */
    private final Set<String> warmedHosts = ConcurrentHashMap.newKeySet();
    private final AtomicInteger userAgentCursor = new AtomicInteger(0);
    private final AtomicLong requestCount = new AtomicLong(0);

    public HttpFetcher(CrawlerProperties properties) {
        this.properties = properties;
        this.client = newClient(false);
        if (properties.getRequest().isInsecureTls()) {
            log.warn("crawler.request.insecure-tls=true：所有 HTTPS 请求将跳过证书校验");
        } else if (!properties.getRequest().getInsecureTlsHosts().isEmpty()) {
            log.warn("以下域名的 HTTPS 证书校验将被跳过（crawler.request.insecure-tls-hosts）：{}",
                    properties.getRequest().getInsecureTlsHosts());
        }
    }

    private HttpClient newClient(boolean insecure) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getRequest().getConnectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .cookieHandler(cookieManager)
                .version(HttpClient.Version.HTTP_1_1);
        if (insecure) {
            builder.sslContext(insecureSslContext());
            builder.sslParameters(HostnameVerificationInitializer.relaxedSslParameters());
        }
        return builder.build();
    }

    /**
     * 信任所有证书的 SSLContext。
     *
     * <p>仅用于证书已过期 / 自签名的政府站点（如安庆阳光采购平台）。
     * 只放宽"证书链信任"，不做降级加密，也不需要关闭主机名校验——
     * 这类站点的证书主机名通常是正确的，只是过了有效期。
     */
    private static SSLContext insecureSslContext() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    // 有意跳过
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    // 有意跳过
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, new SecureRandom());
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("初始化跳过证书校验的 SSLContext 失败", e);
        }
    }

    /**
     * 按目标域名选择客户端。
     */
    private HttpClient clientFor(String url) {
        if (properties.getRequest().isInsecureTls()) {
            return insecureClient();
        }
        String host = hostOf(url);
        if (learnedInsecureHosts.contains(host)) {
            return insecureClient();
        }
        List<String> insecureHosts = properties.getRequest().getInsecureTlsHosts();
        if (insecureHosts != null && !insecureHosts.isEmpty()) {
            for (String insecureHost : insecureHosts) {
                if (insecureHost != null && !insecureHost.isBlank()
                        && host.equalsIgnoreCase(insecureHost.trim())) {
                    return insecureClient();
                }
            }
        }
        return client;
    }

    /**
     * 判断异常链里是否为证书相关错误（过期 / 主机名不匹配 / 证书链无法构建）。
     */
    static boolean isCertificateError(Throwable error) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = error; current != null && visited.add(current); current = current.getCause()) {
            if (current instanceof CertificateException || current instanceof CertPathValidatorException) {
                return true;
            }
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            String lower = message.toLowerCase();
            if (lower.contains("pkix") || lower.contains("unable to find valid certification path")
                    || lower.contains("no subject alternative") || lower.contains("certificateexpired")
                    || lower.contains("certpathvalidator") || lower.contains("certificate")) {
                return true;
            }
        }
        return false;
    }

    private HttpClient insecureClient() {
        HttpClient local = insecureClient;
        if (local == null) {
            synchronized (this) {
                if (insecureClient == null) {
                    insecureClient = newClient(true);
                }
                local = insecureClient;
            }
        }
        return local;
    }

    public long getRequestCount() {
        return requestCount.get();
    }

    public void resetRequestCount() {
        requestCount.set(0);
    }

    /** GET 请求，返回原始字节。 */
    public byte[] getBytes(String url, String referer) {
        return execute("GET", url, null, referer).body();
    }

    /** GET 请求，返回文本（按响应头字符集解码）。 */
    public String getText(String url, String referer) {
        return execute("GET", url, null, referer).asString();
    }

    /** GET 请求，返回完整响应对象（便于判断状态码）。 */
    public FetchResponse get(String url, String referer) {
        return execute("GET", url, null, referer);
    }

    /** POST JSON 请求。 */
    public FetchResponse postJson(String url, String jsonBody, String referer) {
        return execute("POST", url, jsonBody, referer);
    }

    private FetchResponse execute(String method, String url, String jsonBody, String referer) {
        int maxAttempts = Math.max(1, properties.getRequest().getRetryTimes());
        long backoff = properties.getRequest().getRetryBackoffMillis();
        CrawlException lastError = null;
        boolean certFallbackTried = false;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            throttle(url);
            warmUpIfNeeded(url);
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(toUri(url))
                        .timeout(Duration.ofSeconds(properties.getRequest().getTimeoutSeconds()))
                        .header("User-Agent", nextUserAgent())
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,application/json;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                        .header("Accept-Encoding", "gzip, deflate")
                        .header("Cache-Control", "no-cache");

                if (referer != null && !referer.isBlank()) {
                    builder.header("Referer", referer);
                }

                if ("POST".equals(method)) {
                    builder.header("Content-Type", "application/json;charset=UTF-8");
                    builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "{}" : jsonBody,
                            StandardCharsets.UTF_8));
                } else {
                    builder.GET();
                }

                requestCount.incrementAndGet();
                HttpResponse<byte[]> response = clientFor(url).send(builder.build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();
                byte[] body = decompress(response);
                FetchResponse result = new FetchResponse(status, body,
                        response.headers().firstValue("Content-Type").orElse(""), response.uri().toString());

                if (isRetryable(status) && attempt < maxAttempts) {
                    // 403 多半是 WAF cookie 过期：清掉该域名 cookie 并重新预热，
                    // 再走下一次重试（沿用上一版系统"403 重新获取 cookie"的经验）
                    if (status == 403 && properties.getRequest().isWarmUp()) {
                        refreshCookies(url);
                    }
                    long sleep = backoff * attempt + random.nextInt(400);
                    log.warn("HTTP {} 命中重试（第 {}/{} 次），{}ms 后重试：{}", status, attempt, maxAttempts, sleep, url);
                    sleepQuietly(sleep);
                    continue;
                }
                if (isRetryable(status)) {
                    log.warn("HTTP {} 重试耗尽：{}", status, url);
                }
                return result;

            } catch (IOException e) {
                // 证书链在 JDK 信任库中无法构建时自动降级（不消耗重试次数）
                if (!certFallbackTried && properties.getRequest().isCertFallbackToInsecure()
                        && isCertificateError(e)) {
                    certFallbackTried = true;
                    String host = hostOf(url);
                    learnedInsecureHosts.add(host);
                    log.warn("{} 的 HTTPS 证书无法通过 JDK 信任库校验（{}），本次已自动降级为跳过证书校验；"
                                    + "若需严格校验，请把该站点证书链导入 JDK truststore，"
                                    + "或设置 crawler.request.cert-fallback-to-insecure=false",
                            host, e.getMessage());
                    attempt--;
                    continue;
                }
                lastError = new CrawlException("请求失败: " + url + "（" + e.getClass().getSimpleName()
                        + ": " + e.getMessage() + "）", e);
                if (attempt < maxAttempts) {
                    sleepQuietly(backoff * attempt + random.nextInt(400));
                } else {
                    log.warn("请求最终失败（已重试 {} 次）：{} -> {}", maxAttempts, url, e.toString());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CrawlException("请求被中断: " + url, e);
            }
        }
        throw lastError != null ? lastError : new CrawlException("请求失败: " + url);
    }

    private boolean isRetryable(int status) {
        return status == 429 || status == 403 || status >= 500;
    }

    /**
     * 首次访问某域名前先 GET 一次站点首页。
     *
     * <p>政务站点普遍有 WAF（如安庆的 {@code __jsluid_s}），首页会下发 cookie，
     * 直接打接口容易被拦。每个域名只预热一次；失败也记入已预热，避免反复重试。
     */
    private void warmUpIfNeeded(String url) {
        if (!properties.getRequest().isWarmUp()) {
            return;
        }
        String host = hostOf(url);
        if (!warmedHosts.add(host)) {
            return;
        }
        sendRaw(homeUrlOf(url), "预热");
    }

    /** 清掉某域名的 cookie 并重新预热（403 时用）。 */
    private void refreshCookies(String url) {
        String host = hostOf(url);
        try {
            for (HttpCookie cookie : List.copyOf(cookieManager.getCookieStore().getCookies())) {
                String domain = cookie.getDomain();
                if (domain != null && (host.equalsIgnoreCase(domain) || host.endsWith(domain))) {
                    cookieManager.getCookieStore().remove(null, cookie);
                }
            }
        } catch (Exception e) {
            log.debug("清理 {} 的 cookie 失败：{}", host, e.getMessage());
        }
        log.info("{} 返回 403，已清理 cookie 并重新预热", host);
        sendRaw(homeUrlOf(url), "重新预热");
    }

    /** 直接发送一个 GET（不进入 execute，避免与预热逻辑相互递归）。 */
    private void sendRaw(String url, String purpose) {
        try {
            requestCount.incrementAndGet();
            HttpRequest request = HttpRequest.newBuilder(toUri(url))
                    .timeout(Duration.ofSeconds(properties.getRequest().getTimeoutSeconds()))
                    .header("User-Agent", nextUserAgent())
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = clientFor(url).send(request, HttpResponse.BodyHandlers.ofByteArray());
            log.debug("{} {} -> HTTP {}", purpose, url, response.statusCode());
        } catch (Exception e) {
            log.debug("{} {} 失败（忽略）：{}", purpose, url, e.getMessage());
        }
    }

    private String homeUrlOf(String url) {
        try {
            URI uri = URI.create(encodeUrl(url));
            return uri.getScheme() + "://" + uri.getHost() + "/";
        } catch (Exception e) {
            return url;
        }
    }

    /** 同域名最小请求间隔控制。 */
    private void throttle(String url) {
        String host = hostOf(url);
        Object lock = hostLocks.computeIfAbsent(host, k -> new Object());
        long minInterval = properties.getRequest().getMinIntervalMillis();
        synchronized (lock) {
            long now = System.currentTimeMillis();
            long last = hostLastRequestAt.getOrDefault(host, 0L);
            long wait = minInterval - (now - last);
            if (wait > 0) {
                sleepQuietly(wait + random.nextInt(150));
            }
            hostLastRequestAt.put(host, System.currentTimeMillis());
        }
    }

    private String hostOf(String url) {
        try {
            String host = URI.create(encodeUrl(url)).getHost();
            return host == null ? url : host;
        } catch (Exception e) {
            return url;
        }
    }

    private String nextUserAgent() {
        List<String> agents = properties.getUserAgents();
        if (agents == null || agents.isEmpty()) {
            return DEFAULT_UA;
        }
        return agents.get(Math.floorMod(userAgentCursor.getAndIncrement(), agents.size()));
    }

    private byte[] decompress(HttpResponse<byte[]> response) throws IOException {
        byte[] raw = response.body();
        if (raw == null || raw.length == 0) {
            return new byte[0];
        }
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

    private static void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 把可能含空格 / 中文 / 竖线的地址转成合法 URI。
     */
    private static URI toUri(String url) {
        try {
            return URI.create(encodeUrl(url));
        } catch (IllegalArgumentException e) {
            throw new CrawlException("非法 URL: " + url, e);
        }
    }

    /**
     * 把可能含空格 / 中文 / 竖线的地址转成合法 URI。
     */
    public static String encodeUrl(String url) {
        StringBuilder sb = new StringBuilder(url.length() + 32);
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c > 127 || c == ' ' || c == '|') {
                byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                for (byte b : bytes) {
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
