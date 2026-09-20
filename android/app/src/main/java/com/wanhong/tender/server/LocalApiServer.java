package com.wanhong.tender.server;

import android.content.Context;
import android.util.Log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wanhong.crawler.filter.CrawlerKeyword;
import com.wanhong.crawler.pipeline.CrawlLogRecord;
import com.wanhong.crawler.pipeline.CrawlSummary;
import com.wanhong.crawler.pipeline.DeadlineReminder;
import com.wanhong.crawler.pipeline.PageResult;
import com.wanhong.crawler.pipeline.TenderQuery;
import com.wanhong.crawler.pipeline.TenderQueryService;
import com.wanhong.crawler.pipeline.TenderRecord;
import com.wanhong.tender.core.CrawlerHolder;
import com.wanhong.tender.crawl.CrawlScheduler;
import com.wanhong.tender.crawl.ReminderNotifier;
import com.wanhong.tender.crawl.ReminderScheduler;
import com.wanhong.tender.crawl.ReminderWorker;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * 应用内的本地 HTTP 服务（只监听 127.0.0.1）。
 *
 * <p>为什么用这种方式复用页面：现有手机端页面已经调好并验证过，把它原样放进 assets，
 * 由本地服务提供同样的 REST 接口，WebView 指向 127.0.0.1 即可，
 * <b>页面代码一行都不用改</b>；同时用户也可以用手机浏览器打开同一个地址查看。
 */
public class LocalApiServer extends NanoHTTPD {

    private static final String TAG = "LocalApiServer";
    public static final int PORT = 8765;

    private final Context context;
    private final CrawlerHolder holder;
    private final ObjectMapper mapper = com.wanhong.crawler.pipeline.Json.mapper();

    public LocalApiServer(Context context) {
        super("127.0.0.1", PORT);
        this.context = context.getApplicationContext();
        this.holder = CrawlerHolder.get(context);
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();
        Map<String, String> params = new LinkedHashMap<>(session.getParms());
        try {
            if (uri == null || "/".equals(uri) || "/index.html".equals(uri)) {
                return asset("index.html", "text/html");
            }
            if (uri.startsWith("/api/")) {
                return api(uri, method, params);
            }
            return asset(uri.startsWith("/") ? uri.substring(1) : uri, guessType(uri));
        } catch (IllegalArgumentException e) {
            return error(Response.Status.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "接口处理失败: " + uri, e);
            return error(Response.Status.INTERNAL_ERROR, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 路由

    private Response api(String uri, Method method, Map<String, String> params) throws Exception {
        // ---- 公告 ----
        if ("/api/tenders".equals(uri) && method == Method.GET) {
            return json(listTenders(params));
        }
        if ("/api/tenders/stats".equals(uri)) {
            return json(stats());
        }
        if ("/api/tenders/mark-read".equals(uri) && method == Method.POST) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("markedRead", holder.queryService.markAllRead());
            result.put("newCount", holder.queryService.countNew());
            return json(result);
        }
        if ("/api/tenders/export.csv".equals(uri)) {
            return csv(holder.queryService.exportCsv(buildQuery(params), intParam(params, "limit", 5000)));
        }
        if (uri.startsWith("/api/tenders/")) {
            return tenderById(uri, method, params);
        }

        // ---- 关键词 ----
        if ("/api/keywords".equals(uri)) {
            if (method == Method.POST) {
                return json(holder.keywordService.add(params.get("keyword"),
                        com.wanhong.crawler.pipeline.KeywordService.parseScope(params.get("scope")),
                        params.get("note")));
            }
            if (method == Method.DELETE) {
                return json(holder.keywordService.remove(params.get("keyword"),
                        com.wanhong.crawler.pipeline.KeywordService.parseScope(params.get("scope"))));
            }
            return json(holder.keywordService.overview());
        }
        if ("/api/keywords/rescan".equals(uri) && method == Method.POST) {
            return json(holder.keywordService.rescan());
        }
        if ("/api/keywords/reload".equals(uri) && method == Method.POST) {
            holder.keywordRules.reload();
            return json(holder.keywordService.counts());
        }
        if ("/api/keywords/scopes".equals(uri)) {
            return json(com.wanhong.crawler.pipeline.KeywordService.scopes());
        }
        if ("/api/keywords/search-terms".equals(uri)) {
            List<Map<String, String>> terms = new ArrayList<>();
            for (com.wanhong.crawler.crawler.model.SearchTerm term : holder.keywordRules.getSearchTerms()) {
                Map<String, String> item = new LinkedHashMap<>();
                item.put("keyword", term.keyword());
                item.put("fields", term.fields());
                item.put("scope", term.scope());
                terms.add(item);
            }
            return json(terms);
        }

        // ---- 采集 ----
        if ("/api/crawl/run".equals(uri) && method == Method.POST) {
            String site = param(params, "site", "all");
            Integer maxKeywords = intParamOrNull(params, "maxKeywords");
            Integer lookbackDays = intParamOrNull(params, "lookbackDays");
            if (lookbackDays != null) {
                holder.setLookbackDays(lookbackDays);
            }
            // 交给 WorkManager 以前台服务方式跑，避免被系统中断
            CrawlScheduler.runNow(context, site, maxKeywords, lookbackDays);
            return json(status());
        }
        if ("/api/crawl/status".equals(uri)) {
            return json(status());
        }
        if ("/api/crawl/verify".equals(uri) && method == Method.POST) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("checked", holder.linkVerifier.verify(intParamOrNull(params, "limit"), null));
            result.put("pending", holder.linkVerifier.pendingCount());
            return json(result);
        }
        // 立即检查一次截止提醒（设置页上的按钮）：走与定时任务完全相同的判定逻辑，
        // 用来确认"提醒到底配好没有"，不用等到第二天。
        if ("/api/reminder/check".equals(uri) && method == Method.POST) {
            List<TenderRecord> due = ReminderWorker.findDue(context);
            if (!due.isEmpty()) {
                ReminderNotifier.notifyDue(context, due);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("due", due.size());
            result.put("withinDays", holder.getReminderDays());
            result.put("message", due.isEmpty() ? "" : DeadlineReminder.buildMessage(due));
            return json(result);
        }
        if ("/api/crawl/logs".equals(uri)) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (CrawlLogRecord log : holder.repository.recentLogs(intParam(params, "limit", 20))) {
                list.add(TenderQueryService.toLogItem(log, siteName(log.siteCode), false));
            }
            return json(list);
        }
        if (uri.startsWith("/api/crawl/logs/")) {
            long id = Long.parseLong(uri.substring("/api/crawl/logs/".length()));
            CrawlLogRecord log = holder.repository.findLog(id);
            if (log == null) {
                return error(Response.Status.NOT_FOUND, "日志不存在：" + id);
            }
            return json(TenderQueryService.toLogItem(log, siteName(log.siteCode), true));
        }
        if ("/api/crawl/sites".equals(uri)) {
            return json(siteStatuses());
        }

        // ---- 应用设置 ----
        if ("/api/app/settings".equals(uri)) {
            if (method == Method.POST) {
                applySettings(params);
            }
            return json(appSettings());
        }

        return error(Response.Status.NOT_FOUND, "接口不存在：" + uri);
    }

    private Response tenderById(String uri, Method method, Map<String, String> params) throws Exception {
        String rest = uri.substring("/api/tenders/".length());
        if (rest.endsWith("/track")) {
            if (method != Method.POST) {
                return error(Response.Status.METHOD_NOT_ALLOWED, "请使用 POST");
            }
            long id = Long.parseLong(rest.substring(0, rest.length() - "/track".length()));
            boolean tracked = !"false".equalsIgnoreCase(param(params, "tracked", "true"));
            if (!holder.queryService.setTracked(id, tracked)) {
                return error(Response.Status.NOT_FOUND, "公告不存在：" + id);
            }
            TenderRecord record = holder.queryService.findById(id);
            return json(TenderQueryService.toDetail(record));
        }
        long id = Long.parseLong(rest);
        TenderRecord record = holder.queryService.findById(id);
        if (record == null) {
            return error(Response.Status.NOT_FOUND, "公告不存在：" + id);
        }
        return json(TenderQueryService.toDetail(record));
    }

    // ------------------------------------------------------------------ 数据组装

    private Map<String, Object> listTenders(Map<String, String> params) {
        TenderQuery query = buildQuery(params);
        PageResult<TenderRecord> page = holder.queryService.query(query,
                intParam(params, "page", 0), intParam(params, "size", 20));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", TenderQueryService.toListItems(page.content));
        result.put("page", page.page);
        result.put("size", page.size);
        result.put("totalElements", page.totalElements);
        result.put("totalPages", page.totalPages);
        // 告诉界面"因为只看可投而隐藏了多少条"，否则用户只会看到列表莫名其妙变短
        long hidden = 0;
        if (query.onlyBiddable) {
            TenderQuery withAll = query.copy();
            withAll.onlyBiddable = false;
            hidden = Math.max(0, holder.queryService.count(withAll) - page.totalElements);
        }
        result.put("hiddenNonBiddable", hidden);
        return result;
    }

    private TenderQuery buildQuery(Map<String, String> params) {
        TenderQuery query = new TenderQuery();
        query.siteCode = blankToNull(params.get("site"));
        query.keyword = blankToNull(params.get("keyword"));
        query.region = blankToNull(params.get("region"));
        query.fromTime = TenderQueryService.startOfDay(dateParam(params, "from"));
        query.toTime = TenderQueryService.endOfDay(dateParam(params, "to"));
        query.onlyOpen = boolParam(params, "onlyOpen");
        query.onlyNew = boolParam(params, "onlyNew");
        query.trackedOnly = boolParam(params, "tracked");
        query.onlyBiddable = boolParam(params, "onlyBiddable");
        query.includeRemoved = boolParam(params, "includeRemoved");
        query.sortBy = param(params, "sort", TenderQuery.SORT_PUBLISH);
        return query;
    }

    private Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        Map<String, Long> counts = holder.queryService.countBySite(holder.siteCodes());
        long total = 0;
        for (Long value : counts.values()) {
            total += value;
        }
        stats.put("countBySite", counts);
        stats.put("total", total);
        stats.put("newCount", holder.queryService.countNew());
        stats.put("openCount", holder.queryService.countOpen());
        stats.put("trackedCount", holder.queryService.countTracked());
        stats.put("countByRegion", holder.queryService.countByRegion());
        stats.put("knownRegions", com.wanhong.crawler.common.RegionNames.knownRegions());
        stats.put("sites", siteStatuses());
        return stats;
    }

    private List<Map<String, Object>> siteStatuses() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (String code : holder.siteCodes()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("siteCode", code);
            item.put("siteName", siteName(code));
            item.put("enabled", true);
            item.put("totalCount", holder.repository.countBySite(code));
            CrawlLogRecord last = lastLogFor(code);
            if (last != null) {
                item.put("lastCrawlTime", last.startTime);
                item.put("lastCrawlStatus", last.status);
                item.put("lastSavedCount", last.savedCount);
            }
            list.add(item);
        }
        return list;
    }

    private CrawlLogRecord lastLogFor(String siteCode) {
        for (CrawlLogRecord log : holder.repository.recentLogs(30)) {
            if (siteCode.equals(log.siteCode)) {
                return log;
            }
        }
        return null;
    }

    private String siteName(String code) {
        if ("aqggzy".equals(code)) {
            return holder.settings.sites.aqggzy.name;
        }
        if ("cgj".equals(code)) {
            return holder.settings.sites.cgj.name;
        }
        return holder.settings.sites.wyxmzx.name;
    }

    private Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("running", holder.engine.isRunning());
        status.put("site", holder.engine.getRunningSite());
        status.put("since", holder.engine.getRunningSince());
        status.put("requestCount", holder.fetcher.getRequestCount());
        // 进程内的汇总优先（最新，且可能正在跑）；进程重启后它一定是 null，
        // 这时回退到上次采集落下的快照，避免「采集」页显示"尚未执行采集"
        // 而「日志」页明明有历史记录这种自相矛盾的状态。
        CrawlSummary last = holder.engine.getLastSummary();
        if (last == null) {
            last = holder.loadLastSummary();
        }
        status.put("lastSummary", last == null ? null : TenderQueryService.toSummaryItem(last));
        status.put("insecureHosts", holder.transport.insecureHosts());
        return status;
    }

    private Map<String, Object> appSettings() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("scheduleEnabled", holder.isScheduleEnabled());
        map.put("scheduleHour", holder.getScheduleHour());
        map.put("lookbackDays", holder.getLookbackDays());
        map.put("fetchDetail", holder.isFetchDetail());
        map.put("verifyLink", holder.isVerifyLink());
        map.put("reminderEnabled", holder.isReminderEnabled());
        map.put("reminderDays", holder.getReminderDays());
        map.put("insecureHosts", holder.transport.insecureHosts());
        map.put("dbPath", context.getDatabasePath(com.wanhong.tender.data.TenderDbHelper.DB_NAME).getAbsolutePath());
        return map;
    }

    private void applySettings(Map<String, String> params) {
        if (params.containsKey("scheduleEnabled")) {
            holder.setScheduleEnabled(boolParam(params, "scheduleEnabled"));
        }
        if (params.containsKey("scheduleHour")) {
            holder.setScheduleHour(intParam(params, "scheduleHour", holder.getScheduleHour()));
        }
        if (params.containsKey("lookbackDays")) {
            holder.setLookbackDays(intParam(params, "lookbackDays", holder.getLookbackDays()));
        }
        if (params.containsKey("fetchDetail")) {
            holder.setFetchDetail(boolParam(params, "fetchDetail"));
        }
        if (params.containsKey("verifyLink")) {
            holder.setVerifyLink(boolParam(params, "verifyLink"));
        }
        if (params.containsKey("reminderEnabled")) {
            holder.setReminderEnabled(boolParam(params, "reminderEnabled"));
        }
        if (params.containsKey("reminderDays")) {
            holder.setReminderDays(intParam(params, "reminderDays", holder.getReminderDays()));
        }
        CrawlScheduler.schedule(context);
        // 提醒任务的触发时间跟着"每天几点采集"走，收藏/时间改了都要重排
        ReminderScheduler.schedule(context);
    }

    // ------------------------------------------------------------------ 响应工具

    private Response json(Object body) throws Exception {
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8",
                mapper.writeValueAsString(body));
    }

    private Response csv(String content) {
        Response response = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/csv; charset=utf-8", content);
        response.addHeader("Content-Disposition", "attachment; filename=\"flower-tender.csv\"");
        return response;
    }

    private Response error(Response.Status status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.getRequestStatus());
        body.put("message", message == null ? "未知错误" : message);
        try {
            return NanoHTTPD.newFixedLengthResponse(status, "application/json; charset=utf-8",
                    mapper.writeValueAsString(body));
        } catch (Exception e) {
            return NanoHTTPD.newFixedLengthResponse(status, "text/plain; charset=utf-8",
                    String.valueOf(message));
        }
    }

    private Response asset(String name, String mime) {
        InputStream stream = null;
        try {
            stream = context.getAssets().open(name);
            Response response = NanoHTTPD.newChunkedResponse(Response.Status.OK, mime + "; charset=utf-8", stream);
            response.addHeader("Cache-Control", "no-store");
            return response;
        } catch (IOException e) {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // 忽略
                }
            }
            return error(Response.Status.NOT_FOUND, "资源不存在：" + name);
        }
    }

    private String guessType(String uri) {
        if (uri.endsWith(".css")) {
            return "text/css";
        }
        if (uri.endsWith(".js")) {
            return "application/javascript";
        }
        if (uri.endsWith(".png")) {
            return "image/png";
        }
        if (uri.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "text/plain";
    }

    private static String param(Map<String, String> params, String key, String defaultValue) {
        String value = params.get(key);
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static boolean boolParam(Map<String, String> params, String key) {
        return "true".equalsIgnoreCase(param(params, key, "false"));
    }

    private static int intParam(Map<String, String> params, String key, int defaultValue) {
        try {
            String value = params.get(key);
            return value == null || value.trim().isEmpty() ? defaultValue : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static Integer intParamOrNull(Map<String, String> params, String key) {
        try {
            String value = params.get(key);
            return value == null || value.trim().isEmpty() ? null : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate dateParam(Map<String, String> params, String key) {
        try {
            String value = params.get(key);
            return value == null || value.trim().isEmpty() ? null : LocalDate.parse(value.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** 供页面判断是否运行在 App 内 */
    static String utf8(byte[] bytes) {
        return new String(bytes == null ? new byte[0] : bytes, StandardCharsets.UTF_8);
    }

    static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}




