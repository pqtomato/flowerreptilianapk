package com.wanhong.tender.net;

import com.wanhong.crawler.config.CrawlerSettings;
import com.wanhong.crawler.http.FetchResponse;
import com.wanhong.crawler.http.HttpTransport;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 安卓侧的网络实现：OkHttp。
 *
 * <p>职责边界：
 * <ul>
 *   <li>内核负责 UA 轮换、限速、重试、cookie 预热时机；本类只负责"把请求发出去"；</li>
 *   <li>gzip：内核不设置 Accept-Encoding，由 OkHttp 声明并 <b>透明解压</b>；</li>
 *   <li>cookie：本类维护一个按域名分组的容器，自动带回；</li>
 *   <li>证书：只有 {@code settings.request.insecureTlsHosts} 里的域名才放宽校验 ——
 *       安庆三个站点的证书分别是"链不完整"和"已过期 + SAN 不匹配"，在安卓上按域名放宽比
 *       全局关闭安全得多；</li>
 *   <li><b>不发送 Origin</b>：阳光采购平台的 WAF 会因此判定跨站请求伪造。</li>
 * </ul>
 */
public class OkHttpTransport implements HttpTransport {

    private static final MediaType JSON = MediaType.parse("application/json;charset=UTF-8");

    private final CrawlerSettings settings;
    private final OkHttpClient normalClient;
    private final OkHttpClient insecureClient;
    private final Map<String, List<Cookie>> cookieStore = new ConcurrentHashMap<>();

    public OkHttpTransport(CrawlerSettings settings) {
        this.settings = settings;

        CookieJar cookieJar = new CookieJar() {
            @Override
            public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
                if (cookies == null || cookies.isEmpty()) {
                    return;
                }
                List<Cookie> existing = new ArrayList<>(
                        cookieStore.containsKey(url.host()) ? cookieStore.get(url.host()) : new ArrayList<Cookie>());
                for (Cookie cookie : cookies) {
                    existing.removeIf(item -> item.name().equals(cookie.name()));
                    existing.add(cookie);
                }
                cookieStore.put(url.host(), existing);
            }

            @Override
            public List<Cookie> loadForRequest(HttpUrl url) {
                List<Cookie> cookies = cookieStore.get(url.host());
                return cookies == null ? Collections.<Cookie>emptyList() : cookies;
            }
        };

        this.normalClient = buildClient(cookieJar, false);
        this.insecureClient = buildClient(cookieJar, true);
    }

    private OkHttpClient buildClient(CookieJar cookieJar, boolean insecure) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .cookieJar(cookieJar)
                .connectTimeout(settings.request.connectTimeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(settings.request.timeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(settings.request.timeoutSeconds, TimeUnit.SECONDS)
                .followRedirects(true)
                .retryOnConnectionFailure(true);
        if (insecure) {
            try {
                final X509TrustManager trustAll = new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                };
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, new TrustManager[]{trustAll}, new SecureRandom());
                SSLSocketFactory socketFactory = context.getSocketFactory();
                builder.sslSocketFactory(socketFactory, trustAll);
                // 证书主机名也可能对不上（wyxmzx 的证书里没有该域名），这里一并放宽
                builder.hostnameVerifier(new HostnameVerifier() {
                    @Override
                    public boolean verify(String hostname, SSLSession session) {
                        return true;
                    }
                });
            } catch (Exception ignored) {
                // 初始化失败则退化为普通校验
            }
        }
        return builder.build();
    }

    private boolean isInsecureHost(String host) {
        List<String> hosts = settings.request.insecureTlsHosts;
        if (hosts == null || host == null) {
            return false;
        }
        for (String item : hosts) {
            if (item != null && item.trim().equalsIgnoreCase(host)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public FetchResponse send(String method, String url, Map<String, String> headers, byte[] body)
            throws IOException {
        Request.Builder builder = new Request.Builder().url(url);
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                // Accept-Encoding 交给 OkHttp 处理，才能获得透明解压
                if ("Accept-Encoding".equalsIgnoreCase(entry.getKey())) {
                    continue;
                }
                builder.header(entry.getKey(), entry.getValue());
            }
        }
        if ("POST".equals(method)) {
            builder.post(RequestBody.create(body == null ? new byte[0] : body, JSON));
        } else {
            builder.get();
        }

        HttpUrl httpUrl = HttpUrl.parse(url);
        boolean insecure = httpUrl != null && isInsecureHost(httpUrl.host());
        Response response = (insecure ? insecureClient : normalClient).newCall(builder.build()).execute();
        try {
            ResponseBody responseBody = response.body();
            byte[] bytes = responseBody == null ? new byte[0] : responseBody.bytes();
            String contentType = response.header("Content-Type", "");
            return new FetchResponse(response.code(), bytes, contentType, response.request().url().toString());
        } finally {
            response.close();
        }
    }

    @Override
    public void clearCookies(String host) {
        if (host != null) {
            cookieStore.remove(host);
        }
    }

    @Override
    public String name() {
        return "OkHttp";
    }

    /** 供页面展示：当前放宽证书校验的域名 */
    public List<String> insecureHosts() {
        return new ArrayList<>(settings.request.insecureTlsHosts);
    }
}
