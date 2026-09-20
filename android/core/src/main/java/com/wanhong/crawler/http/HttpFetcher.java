package com.wanhong.crawler.http;

import com.wanhong.crawler.config.CrawlerSettings;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 统一抓取器：UA 轮换、同域名限速、失败退避重试、WAF cookie 预热、证书错误自动降级。
 *
 * <p>真正的网络发送交给 {@link HttpTransport}，所以同一份策略代码在安卓上用 OkHttp、
 * 在电脑上用 JDK HttpClient。
 */
public class HttpFetcher {

    /** 回调接口：把关键事件暴露给宿主（安卓上写进日志/通知） */
    public interface Logger {
        void warn(String message);

        void info(String message);

        void debug(String message);

        Logger SILENT = new Logger() {
            public void warn(String message) {
            }

            public void info(String message) {
            }

            public void debug(String message) {
            }
        };
    }

    private final CrawlerSettings settings;
    private final HttpTransport transport;
    private final Logger logger;
    private final Random random = new Random();

    /** 每个域名的串行锁，保证同域名请求按最小间隔顺序发出 */
    private final Map<String, Object> hostLocks = new ConcurrentHashMap<>();
    private final Map<String, Long> hostLastRequestAt = new ConcurrentHashMap<>();
    /** 已预热过的域名（每域名一次） */
    private final Set<String> warmedHosts = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final AtomicInteger userAgentCursor = new AtomicInteger(0);
    private final AtomicLong requestCount = new AtomicLong(0);

    public HttpFetcher(CrawlerSettings settings, HttpTransport transport, Logger logger) {
        this.settings = settings;
        this.transport = transport;
        this.logger = logger == null ? Logger.SILENT : logger;
    }

    public long getRequestCount() {
        return requestCount.get();
    }

    public void resetRequestCount() {
        requestCount.set(0);
    }

    public byte[] getBytes(String url, String referer) {
        return execute("GET", url, null, referer).body();
    }

    public String getText(String url, String referer) {
        return execute("GET", url, null, referer).asString();
    }

    public FetchResponse get(String url, String referer) {
        return execute("GET", url, null, referer);
    }

    public FetchResponse postJson(String url, String jsonBody, String referer) {
        return execute("POST", url, jsonBody, referer);
    }

    private FetchResponse execute(String method, String url, String jsonBody, String referer) {
        int maxAttempts = Math.max(1, settings.request.retryTimes);
        long backoff = settings.request.retryBackoffMillis;
        CrawlException lastError = null;
        boolean certFallbackTried = false;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            throttle(url);
            if (settings.request.warmUp) {
                warmUpIfNeeded(url);
            }
            try {
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("User-Agent", nextUserAgent());
                headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,"
                        + "application/json;q=0.9,*/*;q=0.8");
                headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
                headers.put("Cache-Control", "no-cache");
                // 刻意不设置 Accept-Encoding：让传输层自己声明 gzip 并透明解压
                // （OkHttp 只在"自己添加了该头"时才自动解压）
                if (referer != null && !referer.trim().isEmpty()) {
                    headers.put("Referer", referer);
                }
                byte[] payload = null;
                if ("POST".equals(method)) {
                    headers.put("Content-Type", "application/json;charset=UTF-8");
                    payload = (jsonBody == null ? "{}" : jsonBody).getBytes(StandardCharsets.UTF_8);
                }

                requestCount.incrementAndGet();
                FetchResponse response = transport.send(method, url, headers, payload);
                int status = response.status();

                if (isRetryable(status) && attempt < maxAttempts) {
                    if (status == 403 && settings.request.warmUp) {
                        refreshCookies(url);
                    }
                    long sleep = backoff * attempt + random.nextInt(400);
                    logger.warn("HTTP " + status + " 命中重试（第 " + attempt + "/" + maxAttempts
                            + " 次），" + sleep + "ms 后重试：" + url);
                    sleepQuietly(sleep);
                    continue;
                }
                if (isRetryable(status)) {
                    logger.warn("HTTP " + status + " 重试耗尽：" + url);
                }
                return response;

            } catch (IOException e) {
                if (!certFallbackTried && settings.request.certFallbackToInsecure && isCertificateError(e)) {
                    certFallbackTried = true;
                    String host = hostOf(url);
                    transport.clearCookies(host);
                    if (!settings.request.insecureTlsHosts.contains(host)) {
                        settings.request.insecureTlsHosts.add(host);
                    }
                    logger.warn(host + " 的 HTTPS 证书无法校验（" + e.getMessage()
                            + "），本次已自动降级为跳过证书校验；已加入 insecureTlsHosts 以便后续直连");
                    attempt--;
                    continue;
                }
                lastError = new CrawlException("请求失败: " + url + "（" + e.getClass().getSimpleName()
                        + ": " + e.getMessage() + "）", e);
                if (attempt < maxAttempts) {
                    sleepQuietly(backoff * attempt + random.nextInt(400));
                } else {
                    logger.warn("请求最终失败（已重试 " + maxAttempts + " 次）：" + url + " -> " + e);
                }
            }
        }
        throw lastError != null ? lastError : new CrawlException("请求失败: " + url);
    }

    private boolean isRetryable(int status) {
        return status == 429 || status == 403 || status >= 500;
    }

    /** 首次访问域名前先 GET 一次站点首页，拿 WAF 下发的 cookie。 */
    private void warmUpIfNeeded(String url) {
        String host = hostOf(url);
        if (!warmedHosts.add(host)) {
            return;
        }
        sendRaw(homeUrlOf(url), "预热");
    }

    /** 403 时清理 cookie 并重新预热。 */
    private void refreshCookies(String url) {
        String host = hostOf(url);
        transport.clearCookies(host);
        logger.info(host + " 返回 403，已清理 cookie 并重新预热");
        sendRaw(homeUrlOf(url), "重新预热");
    }

    private void sendRaw(String url, String purpose) {
        try {
            Map<String, String> headers = new HashMap<>();
            headers.put("User-Agent", nextUserAgent());
            headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
            requestCount.incrementAndGet();
            FetchResponse response = transport.send("GET", url, headers, null);
            logger.debug(purpose + " " + url + " -> HTTP " + response.status());
        } catch (Exception e) {
            logger.debug(purpose + " " + url + " 失败（忽略）：" + e.getMessage());
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
        Object lock = hostLocks.get(host);
        if (lock == null) {
            hostLocks.putIfAbsent(host, new Object());
            lock = hostLocks.get(host);
        }
        long minInterval = settings.request.minIntervalMillis;
        synchronized (lock) {
            long now = System.currentTimeMillis();
            Long last = hostLastRequestAt.get(host);
            long wait = minInterval - (now - (last == null ? 0L : last));
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
        List<String> agents = settings.userAgents;
        if (agents == null || agents.isEmpty()) {
            return "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36";
        }
        int index = Math.floorMod(userAgentCursor.getAndIncrement(), agents.size());
        return agents.get(index);
    }

    /** 判断异常链里是否为证书相关错误（过期 / 主机名不匹配 / 证书链无法构建）。 */
    static boolean isCertificateError(Throwable error) {
        Set<Throwable> visited = Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        for (Throwable current = error; current != null && visited.add(current); current = current.getCause()) {
            String name = current.getClass().getName();
            if (name.contains("CertificateException") || name.contains("CertPathValidatorException")
                    || name.contains("SSLHandshakeException") || name.contains("SSLPeerUnverifiedException")) {
                return true;
            }
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            String lower = message.toLowerCase();
            if (lower.contains("pkix") || lower.contains("unable to find valid certification path")
                    || lower.contains("no subject alternative") || lower.contains("certificate")
                    || lower.contains("trust anchor")) {
                return true;
            }
        }
        return false;
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

    /** 把可能含空格 / 中文 / 竖线的地址转成合法 URI。 */
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
