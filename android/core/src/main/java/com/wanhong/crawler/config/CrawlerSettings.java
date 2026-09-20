package com.wanhong.crawler.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 采集配置。
 *
 * <p>安卓版的配置来源是应用内设置（SharedPreferences），因此这里是一个普通 POJO，
 * 不依赖任何框架。字段与旧版 Spring Boot 版一一对应，便于对照。
 */
public class CrawlerSettings {

    /** 总开关 */
    public boolean enabled = true;

    /** 统一时区：截止日期与过期判断都基于它 */
    public String timezone = "Asia/Shanghai";

    /** 随机 UA 池 */
    public List<String> userAgents = new ArrayList<>(Arrays.asList(
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"));

    public Request request = new Request();
    public Filter filter = new Filter();
    public Detail detail = new Detail();
    public Deadline deadline = new Deadline();
    public Verify verify = new Verify();
    public Sites sites = new Sites();

    /** HTTP 请求相关 */
    public static class Request {
        public int connectTimeoutSeconds = 15;
        public int timeoutSeconds = 25;
        /** 失败重试次数（网络异常 / 5xx / 429 / 403） */
        public int retryTimes = 3;
        public long retryBackoffMillis = 1200L;
        /** 同域名最小请求间隔，礼貌抓取 */
        public long minIntervalMillis = 700L;
        /** 单次采集最多抓多少详情页 */
        public int maxDetailPerRun = 300;
        /** 首次访问域名前先 GET 首页拿 WAF cookie */
        public boolean warmUp = true;

        /** 这些域名跳过 HTTPS 证书校验（证书过期 / 证书链不完整） */
        public List<String> insecureTlsHosts = new ArrayList<>(Arrays.asList("www.wyxmzx.com"));
        /** 运行时遇到证书错误自动降级（有日志） */
        public boolean certFallbackToInsecure = true;
    }

    /** 关键词与过滤 */
    public static class Filter {
        public String titleKeywordsFile = "keywords/flower-keywords.txt";
        public String contentKeywordsFile = "keywords/flower-core-keywords.txt";
        public String excludeKeywordsFile = "keywords/exclude-keywords.txt";
        public boolean matchTitle = true;
        public boolean matchContent = true;
        public List<String> extraTitleKeywords = new ArrayList<>();
        public List<String> extraContentKeywords = new ArrayList<>();
        public List<String> excludeKeywords = new ArrayList<>();
    }

    /** 详情页抓取 */
    public static class Detail {
        /** 是否抓取详情页正文（关掉可让采集快约 5 倍，只用检索接口自带正文） */
        public boolean enabled = true;
        /** 正文最大保存长度 */
        public int maxContentLength = 30000;
    }

    /** 截止日期解析与估算 */
    public static class Deadline {
        public boolean parseFromContent = true;
        /** 解析不到时按 发布日期 + N 天 估算 */
        public int defaultDays = 60;
        /** 偏离发布日期超过 N 天视为误判 */
        public int maxDays = 400;
    }

    /** 已下架校验 */
    public static class Verify {
        public boolean enabled = true;
        public int limit = 120;
        /** 同一公告多少小时内不重复校验 */
        public int skipHours = 12;
    }

    /** 站点 */
    public static class Sites {
        public Aqggzy aqggzy = new Aqggzy();
        public Cgj cgj = new Cgj();
        public Wyxmzx wyxmzx = new Wyxmzx();
    }

    public static class Aqggzy {
        public boolean enabled = true;
        public String name = "安庆市公共资源交易服务网";
        public String baseUrl = "https://aqggzy.anqing.gov.cn";
        public String searchPath = "/inteligentsearch/rest/esinteligentsearch/getFullTextDataNew";
        public String referer = "https://aqggzy.anqing.gov.cn/jyxx/003001/construction.html";
        public int pageSize = 30;
        public int maxPagesPerKeyword = 2;
        public int contentLength = 2000;
        /** 只采集最近 N 天；0 = 不限 */
        public int lookbackDays = 30;
        /** 这些分类前缀是新闻资讯，不是招标公告 */
        public List<String> excludedCategoryPrefixes = new ArrayList<>(Arrays.asList("002"));
    }

    public static class Cgj {
        public boolean enabled = true;
        public String name = "安庆市城市管理局-通知公告";
        public String baseUrl = "https://cgj.anqing.gov.cn";
        public String listUrl = "/xxfb/tzgg/";
        public String columnUrl = "/content/column/14015578";
        public int maxPages = 2;
    }

    public static class Wyxmzx {
        public boolean enabled = true;
        public String name = "安庆市阳光采购交易服务平台";
        public String baseUrl = "https://www.wyxmzx.com";
        public String searchPath = "/inteligentsearch/rest/esinteligentsearch/getFullTextDataNew";
        /** 重要：该站 WAF 只要请求带 Referer 就判定跨站请求伪造，必须留空 */
        public String referer = "";
        public String cnum = "080";
        public int pageSize = 30;
        public int maxPagesPerKeyword = 2;
        public int contentLength = 3000;
        public int lookbackDays = 30;
        public List<String> excludedCategoryPrefixes = new ArrayList<>();
    }
}
