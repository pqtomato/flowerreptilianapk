package com.wanhong.crawler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 爬虫配置，映射 application.yml 中 crawler.* 节点。
 */
@ConfigurationProperties(prefix = "crawler")
public class CrawlerProperties {

    /** 总开关 */
    private boolean enabled = true;

    /** 随机 UA 池 */
    private List<String> userAgents = new ArrayList<>();

    /** 统一时区：截止日期、过期判断、"今天"都必须基于同一时区 */
    private String timezone = "Asia/Shanghai";

    private Request request = new Request();
    private Filter filter = new Filter();
    private Schedule schedule = new Schedule();
    private Detail detail = new Detail();
    private Deadline deadline = new Deadline();
    private Verify verify = new Verify();
    private Sites sites = new Sites();

    public Verify getVerify() {
        return verify;
    }

    public void setVerify(Verify verify) {
        this.verify = verify;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public Deadline getDeadline() {
        return deadline;
    }

    public void setDeadline(Deadline deadline) {
        this.deadline = deadline;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getUserAgents() {
        return userAgents;
    }

    public void setUserAgents(List<String> userAgents) {
        this.userAgents = userAgents;
    }

    public Request getRequest() {
        return request;
    }

    public void setRequest(Request request) {
        this.request = request;
    }

    public Filter getFilter() {
        return filter;
    }

    public void setFilter(Filter filter) {
        this.filter = filter;
    }

    public Schedule getSchedule() {
        return schedule;
    }

    public void setSchedule(Schedule schedule) {
        this.schedule = schedule;
    }

    public Detail getDetail() {
        return detail;
    }

    public void setDetail(Detail detail) {
        this.detail = detail;
    }

    public Sites getSites() {
        return sites;
    }

    public void setSites(Sites sites) {
        this.sites = sites;
    }

    /** HTTP 请求相关 */
    public static class Request {
        private int connectTimeoutSeconds = 15;
        private int timeoutSeconds = 25;
        private int retryTimes = 3;
        private long retryBackoffMillis = 1200L;
        private long minIntervalMillis = 700L;
        private int maxDetailPerRun = 300;
        /** 全局跳过 HTTPS 证书校验（慎用） */
        private boolean insecureTls = false;
        /** 仅对这些域名跳过 HTTPS 证书校验（用于证书过期/自签名的站点） */
        private List<String> insecureTlsHosts = new ArrayList<>();
        /**
         * 证书链校验失败时是否自动降级为跳过校验。
         *
         * <p>不少政务站点的 TLS 证书没有下发中间证书，Windows / curl 能靠 AIA 补链，
         * 但 JDK 默认信任库无法构建证书链，会抛 PKIX path building failed。
         * 打开后首次遇到该错误会记 WARN 并自动降级，后续请求直接复用宽松客户端。
         */
        private boolean certFallbackToInsecure = true;
        /** 首次访问某域名前先 GET 一次站点首页，拿到 WAF cookie（如 __jsluid_s） */
        private boolean warmUp = true;
        /** 采集每个站点前先发一次探测请求，失败就跳过该站，避免整轮卡死 */
        private boolean probeBeforeCrawl = true;

        public int getConnectTimeoutSeconds() {
            return connectTimeoutSeconds;
        }

        public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
            this.connectTimeoutSeconds = connectTimeoutSeconds;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public int getRetryTimes() {
            return retryTimes;
        }

        public void setRetryTimes(int retryTimes) {
            this.retryTimes = retryTimes;
        }

        public long getRetryBackoffMillis() {
            return retryBackoffMillis;
        }

        public void setRetryBackoffMillis(long retryBackoffMillis) {
            this.retryBackoffMillis = retryBackoffMillis;
        }

        public long getMinIntervalMillis() {
            return minIntervalMillis;
        }

        public void setMinIntervalMillis(long minIntervalMillis) {
            this.minIntervalMillis = minIntervalMillis;
        }

        public int getMaxDetailPerRun() {
            return maxDetailPerRun;
        }

        public void setMaxDetailPerRun(int maxDetailPerRun) {
            this.maxDetailPerRun = maxDetailPerRun;
        }

        public boolean isInsecureTls() {
            return insecureTls;
        }

        public void setInsecureTls(boolean insecureTls) {
            this.insecureTls = insecureTls;
        }

        public List<String> getInsecureTlsHosts() {
            return insecureTlsHosts;
        }

        public void setInsecureTlsHosts(List<String> insecureTlsHosts) {
            this.insecureTlsHosts = insecureTlsHosts;
        }

        public boolean isCertFallbackToInsecure() {
            return certFallbackToInsecure;
        }

        public void setCertFallbackToInsecure(boolean certFallbackToInsecure) {
            this.certFallbackToInsecure = certFallbackToInsecure;
        }

        public boolean isWarmUp() {
            return warmUp;
        }

        public void setWarmUp(boolean warmUp) {
            this.warmUp = warmUp;
        }

        public boolean isProbeBeforeCrawl() {
            return probeBeforeCrawl;
        }

        public void setProbeBeforeCrawl(boolean probeBeforeCrawl) {
            this.probeBeforeCrawl = probeBeforeCrawl;
        }
    }

    /**
     * 已下架校验配置。
     *
     * <p>参考上一版系统的做法：采集完顺带抽查已入库公告是否还在，
     * 已下架的标记出来不再展示，避免用户点开是死链。
     * 采用「保守判定」——只有明确 404/410 或页面明确提示"信息不存在"才算下架，
     * 网络异常一律认为仍然存在。
     */
    public static class Verify {
        /** 是否在每次采集后自动校验 */
        private boolean enabled = true;
        /** 单次最多校验多少条 */
        private int limit = 150;
        /** 同一公告多少小时内不重复校验 */
        private int skipHours = 12;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getLimit() {
            return limit;
        }

        public void setLimit(int limit) {
            this.limit = limit;
        }

        public int getSkipHours() {
            return skipHours;
        }

        public void setSkipHours(int skipHours) {
            this.skipHours = skipHours;
        }
    }

    /**
     * 截止日期（报名 / 投标截止、开标时间）解析与估算配置。
     *
     * <p>参考上一版 Python 系统的经验：绝大多数站点不提供截止日期字段，
     * 只有池州等少数平台有真实 endTime，其余都靠"发布日期 + N 天"估算，
     * 否则手机上无法判断"这条还能不能投"。
     */
    public static class Deadline {
        /** 解析不到时按 发布日期 + N 天 估算 */
        private int defaultDays = 60;
        /** 解析结果偏离发布日期超过 N 天视为误判，改用估算值 */
        private int maxDays = 400;
        /** 是否启用正文截止日期解析 */
        private boolean parseFromContent = true;

        public int getDefaultDays() {
            return defaultDays;
        }

        public void setDefaultDays(int defaultDays) {
            this.defaultDays = defaultDays;
        }

        public int getMaxDays() {
            return maxDays;
        }

        public void setMaxDays(int maxDays) {
            this.maxDays = maxDays;
        }

        public boolean isParseFromContent() {
            return parseFromContent;
        }

        public void setParseFromContent(boolean parseFromContent) {
            this.parseFromContent = parseFromContent;
        }
    }

    /** 关键词过滤相关 */
    public static class Filter {
        private String titleKeywordsFile = "keywords/flower-keywords.txt";
        private String contentKeywordsFile = "keywords/flower-core-keywords.txt";
        /** 排除词表：命中即丢弃（只判定标题，避免正文顺带提及造成误伤） */
        private String excludeKeywordsFile = "keywords/exclude-keywords.txt";
        private List<String> extraTitleKeywords = new ArrayList<>();
        private List<String> extraContentKeywords = new ArrayList<>();
        private List<String> excludeKeywords = new ArrayList<>();
        private boolean matchTitle = true;
        private boolean matchContent = true;

        public String getExcludeKeywordsFile() {
            return excludeKeywordsFile;
        }

        public void setExcludeKeywordsFile(String excludeKeywordsFile) {
            this.excludeKeywordsFile = excludeKeywordsFile;
        }

        public String getTitleKeywordsFile() {
            return titleKeywordsFile;
        }

        public void setTitleKeywordsFile(String titleKeywordsFile) {
            this.titleKeywordsFile = titleKeywordsFile;
        }

        public String getContentKeywordsFile() {
            return contentKeywordsFile;
        }

        public void setContentKeywordsFile(String contentKeywordsFile) {
            this.contentKeywordsFile = contentKeywordsFile;
        }

        public List<String> getExtraTitleKeywords() {
            return extraTitleKeywords;
        }

        public void setExtraTitleKeywords(List<String> extraTitleKeywords) {
            this.extraTitleKeywords = extraTitleKeywords;
        }

        public List<String> getExtraContentKeywords() {
            return extraContentKeywords;
        }

        public void setExtraContentKeywords(List<String> extraContentKeywords) {
            this.extraContentKeywords = extraContentKeywords;
        }

        public List<String> getExcludeKeywords() {
            return excludeKeywords;
        }

        public void setExcludeKeywords(List<String> excludeKeywords) {
            this.excludeKeywords = excludeKeywords;
        }

        public boolean isMatchTitle() {
            return matchTitle;
        }

        public void setMatchTitle(boolean matchTitle) {
            this.matchTitle = matchTitle;
        }

        public boolean isMatchContent() {
            return matchContent;
        }

        public void setMatchContent(boolean matchContent) {
            this.matchContent = matchContent;
        }
    }

    /** 定时任务 */
    public static class Schedule {
        private boolean enabled = true;
        private String cron = "0 30 2 * * ?";
        private String zone = "Asia/Shanghai";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getCron() {
            return cron;
        }

        public void setCron(String cron) {
            this.cron = cron;
        }

        public String getZone() {
            return zone;
        }

        public void setZone(String zone) {
            this.zone = zone;
        }
    }

    /** 详情页抓取 */
    public static class Detail {
        private boolean enabled = true;
        private int maxContentLength = 30000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxContentLength() {
            return maxContentLength;
        }

        public void setMaxContentLength(int maxContentLength) {
            this.maxContentLength = maxContentLength;
        }
    }

    /** 站点集合 */
    public static class Sites {
        private Aqggzy aqggzy = new Aqggzy();
        private Cgj cgj = new Cgj();
        private Wyxmzx wyxmzx = new Wyxmzx();

        public Aqggzy getAqggzy() {
            return aqggzy;
        }

        public void setAqggzy(Aqggzy aqggzy) {
            this.aqggzy = aqggzy;
        }

        public Cgj getCgj() {
            return cgj;
        }

        public void setCgj(Cgj cgj) {
            this.cgj = cgj;
        }

        public Wyxmzx getWyxmzx() {
            return wyxmzx;
        }

        public void setWyxmzx(Wyxmzx wyxmzx) {
            this.wyxmzx = wyxmzx;
        }
    }

    /** 安庆市公共资源交易服务网 */
    public static class Aqggzy {
        private boolean enabled = true;
        private String name = "安庆市公共资源交易服务网";
        private String baseUrl = "https://aqggzy.anqing.gov.cn";
        private String searchPath = "/inteligentsearch/rest/esinteligentsearch/getFullTextDataNew";
        private String referer = "https://aqggzy.anqing.gov.cn/jyxx/003001/construction.html";
        private String searchFields = "title";
        private int pageSize = 30;
        private int maxPagesPerKeyword = 2;
        private int contentLength = 200;
        /**
         * 只采集最近 N 天内发布的公告；0 表示不限（首次建库要历史数据时用）。
         *
         * <p>参考上一版系统的教训：不加时间窗会捞到大量早已过期的老公告
         * （实测 wyxmzx 关键词"绿化"会返回 2024 年的记录），既浪费详情页配额
         * 又污染手机端的"还能不能投"判断。
         */
        private int lookbackDays = 30;
        private List<String> excludedCategoryPrefixes = new ArrayList<>();

        public int getLookbackDays() {
            return lookbackDays;
        }

        public void setLookbackDays(int lookbackDays) {
            this.lookbackDays = lookbackDays;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getSearchPath() {
            return searchPath;
        }

        public void setSearchPath(String searchPath) {
            this.searchPath = searchPath;
        }

        public String getReferer() {
            return referer;
        }

        public void setReferer(String referer) {
            this.referer = referer;
        }

        public String getSearchFields() {
            return searchFields;
        }

        public void setSearchFields(String searchFields) {
            this.searchFields = searchFields;
        }

        public int getPageSize() {
            return pageSize;
        }

        public void setPageSize(int pageSize) {
            this.pageSize = pageSize;
        }

        public int getMaxPagesPerKeyword() {
            return maxPagesPerKeyword;
        }

        public void setMaxPagesPerKeyword(int maxPagesPerKeyword) {
            this.maxPagesPerKeyword = maxPagesPerKeyword;
        }

        public int getContentLength() {
            return contentLength;
        }

        public void setContentLength(int contentLength) {
            this.contentLength = contentLength;
        }

        public List<String> getExcludedCategoryPrefixes() {
            return excludedCategoryPrefixes;
        }

        public void setExcludedCategoryPrefixes(List<String> excludedCategoryPrefixes) {
            this.excludedCategoryPrefixes = excludedCategoryPrefixes;
        }
    }

    /** 安庆市城市管理局 通知公告 */
    public static class Cgj {
        private boolean enabled = true;
        private String name = "安庆市城市管理局-通知公告";
        private String baseUrl = "https://cgj.anqing.gov.cn";
        private String listUrl = "/xxfb/tzgg/";
        private String columnUrl = "/content/column/14015578";
        private int pageSize = 20;
        private int maxPages = 2;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getListUrl() {
            return listUrl;
        }

        public void setListUrl(String listUrl) {
            this.listUrl = listUrl;
        }

        public String getColumnUrl() {
            return columnUrl;
        }

        public void setColumnUrl(String columnUrl) {
            this.columnUrl = columnUrl;
        }

        public int getPageSize() {
            return pageSize;
        }

        public void setPageSize(int pageSize) {
            this.pageSize = pageSize;
        }

        public int getMaxPages() {
            return maxPages;
        }

        public void setMaxPages(int maxPages) {
            this.maxPages = maxPages;
        }
    }

    /** 安庆市阳光采购交易服务平台 */
    public static class Wyxmzx {
        private boolean enabled = true;
        private String name = "安庆市阳光采购交易服务平台";
        private String baseUrl = "https://www.wyxmzx.com";
        private String searchPath = "/inteligentsearch/rest/esinteligentsearch/getFullTextDataNew";
        private String referer = "https://www.wyxmzx.com/jyxx/002001/bussinessInfo.html";
        private String searchFields = "title";
        private String cnum = "080";
        private int pageSize = 30;
        private int maxPagesPerKeyword = 2;
        private int contentLength = 3000;
        /** 只采集最近 N 天内发布的公告；0 表示不限 */
        private int lookbackDays = 30;
        private List<String> excludedCategoryPrefixes = new ArrayList<>();

        public int getLookbackDays() {
            return lookbackDays;
        }

        public void setLookbackDays(int lookbackDays) {
            this.lookbackDays = lookbackDays;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getSearchPath() {
            return searchPath;
        }

        public void setSearchPath(String searchPath) {
            this.searchPath = searchPath;
        }

        public String getReferer() {
            return referer;
        }

        public void setReferer(String referer) {
            this.referer = referer;
        }

        public String getSearchFields() {
            return searchFields;
        }

        public void setSearchFields(String searchFields) {
            this.searchFields = searchFields;
        }

        public String getCnum() {
            return cnum;
        }

        public void setCnum(String cnum) {
            this.cnum = cnum;
        }

        public int getPageSize() {
            return pageSize;
        }

        public void setPageSize(int pageSize) {
            this.pageSize = pageSize;
        }

        public int getMaxPagesPerKeyword() {
            return maxPagesPerKeyword;
        }

        public void setMaxPagesPerKeyword(int maxPagesPerKeyword) {
            this.maxPagesPerKeyword = maxPagesPerKeyword;
        }

        public int getContentLength() {
            return contentLength;
        }

        public void setContentLength(int contentLength) {
            this.contentLength = contentLength;
        }

        public List<String> getExcludedCategoryPrefixes() {
            return excludedCategoryPrefixes;
        }

        public void setExcludedCategoryPrefixes(List<String> excludedCategoryPrefixes) {
            this.excludedCategoryPrefixes = excludedCategoryPrefixes;
        }
    }
}
