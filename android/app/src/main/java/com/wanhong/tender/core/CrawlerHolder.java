package com.wanhong.tender.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wanhong.crawler.config.CrawlerSettings;
import com.wanhong.crawler.crawler.AqggzyCrawler;
import com.wanhong.crawler.crawler.CgjCrawler;
import com.wanhong.crawler.crawler.DeadlineExtractor;
import com.wanhong.crawler.crawler.DetailParser;
import com.wanhong.crawler.crawler.SiteCrawler;
import com.wanhong.crawler.crawler.WyxmzxCrawler;
import com.wanhong.crawler.crawler.epoint.EpointSearchClient;
import com.wanhong.crawler.filter.KeywordRules;
import com.wanhong.crawler.http.HttpFetcher;
import com.wanhong.crawler.pipeline.CrawlEngine;
import com.wanhong.crawler.pipeline.CrawlSummary;
import com.wanhong.crawler.pipeline.KeywordService;
import com.wanhong.crawler.pipeline.LinkVerifier;
import com.wanhong.crawler.pipeline.TenderQueryService;
import com.wanhong.crawler.pipeline.TenderRepository;
import com.wanhong.tender.data.SqliteKeywordStore;
import com.wanhong.tender.data.SqliteTenderRepository;
import com.wanhong.tender.net.OkHttpTransport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 组装内核：把内核的各部件和安卓侧的实现（OkHttp / SQLite）接在一起。
 *
 * <p>应用内单例。数据集很小（几百到几万条），单例足够，也避免重复建连接。
 */
public final class CrawlerHolder {

    private static final String TAG = "CrawlerHolder";
    private static final String PREFS = "flower_tender";

    private static volatile CrawlerHolder instance;

    public final CrawlerSettings settings;
    public final OkHttpTransport transport;
    public final HttpFetcher fetcher;
    public final KeywordRules keywordRules;
    public final TenderRepository repository;
    public final CrawlEngine engine;
    public final TenderQueryService queryService;
    public final KeywordService keywordService;
    public final LinkVerifier linkVerifier;
    private final SharedPreferences prefs;

    private CrawlerHolder(Context context) {
        Context app = context.getApplicationContext();
        this.prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.settings = new CrawlerSettings();
        applyPrefs();

        this.transport = new OkHttpTransport(settings);
        this.fetcher = new HttpFetcher(settings, transport, new HttpFetcher.Logger() {
            public void warn(String message) {
                Log.w(TAG, message);
            }

            public void info(String message) {
                Log.i(TAG, message);
            }

            public void debug(String message) {
                Log.d(TAG, message);
            }
        });

        SqliteTenderRepository sqliteRepository = new SqliteTenderRepository(app);
        SqliteKeywordStore keywordStore = new SqliteKeywordStore(app);
        this.repository = sqliteRepository;
        this.keywordRules = new KeywordRules(settings, keywordStore, new KeywordRules.Logger() {
            public void info(String message) {
                Log.i(TAG, message);
            }

            public void warn(String message) {
                Log.w(TAG, message);
            }
        });
        this.keywordRules.reload();

        EpointSearchClient searchClient = new EpointSearchClient(fetcher, com.wanhong.crawler.pipeline.Json.mapper(),
                new EpointSearchClient.Logger() {
                    public void warn(String message) {
                        Log.w(TAG, message);
                    }

                    public void error(String message, Throwable error) {
                        Log.e(TAG, message, error);
                    }
                });

        DetailParser detailParser = new DetailParser(settings, message -> Log.w(TAG, message));
        DeadlineExtractor deadlineExtractor = new DeadlineExtractor(settings);
        this.linkVerifier = new LinkVerifier(repository, fetcher, settings);

        List<SiteCrawler> crawlers = new ArrayList<>();
        crawlers.add(new AqggzyCrawler(settings, searchClient));
        crawlers.add(new CgjCrawler(settings, fetcher, message -> Log.w(TAG, message)));
        crawlers.add(new WyxmzxCrawler(settings, searchClient));

        this.engine = new CrawlEngine(settings, crawlers, keywordRules, repository, fetcher,
                detailParser, deadlineExtractor, linkVerifier, com.wanhong.crawler.pipeline.Json.mapper(),
                new CrawlEngine.Logger() {
                    public void info(String message) {
                        Log.i(TAG, message);
                    }

                    public void warn(String message, Throwable error) {
                        Log.w(TAG, message, error);
                    }
                });

        this.queryService = new TenderQueryService(repository);
        this.keywordService = new KeywordService(keywordStore, repository, keywordRules);
    }

    public static CrawlerHolder get(Context context) {
        if (instance == null) {
            synchronized (CrawlerHolder.class) {
                if (instance == null) {
                    instance = new CrawlerHolder(context);
                }
            }
        }
        return instance;
    }

    // ------------------------------------------------------------ 设置项

    private void applyPrefs() {
        settings.sites.aqggzy.lookbackDays = prefs.getInt("lookbackDays", 30);
        settings.sites.wyxmzx.lookbackDays = prefs.getInt("lookbackDays", 30);
        settings.detail.enabled = prefs.getBoolean("fetchDetail", true);
        settings.verify.enabled = prefs.getBoolean("verifyLink", true);
        settings.request.maxDetailPerRun = prefs.getInt("maxDetailPerRun", 300);
        // 三个站点的证书都有问题，这里允许用户手动增删（默认已含 wyxmzx）
        String extra = prefs.getString("insecureHosts", "");
        if (extra != null && !extra.trim().isEmpty()) {
            List<String> hosts = new ArrayList<>(settings.request.insecureTlsHosts);
            for (String host : extra.split(",")) {
                String trimmed = host.trim();
                if (!trimmed.isEmpty() && !hosts.contains(trimmed)) {
                    hosts.add(trimmed);
                }
            }
            settings.request.insecureTlsHosts = hosts;
        }
    }

    public int getLookbackDays() {
        return prefs.getInt("lookbackDays", 30);
    }

    public void setLookbackDays(int days) {
        prefs.edit().putInt("lookbackDays", days).apply();
        settings.sites.aqggzy.lookbackDays = days;
        settings.sites.wyxmzx.lookbackDays = days;
    }

    public boolean isScheduleEnabled() {
        return prefs.getBoolean("scheduleEnabled", true);
    }

    public void setScheduleEnabled(boolean enabled) {
        prefs.edit().putBoolean("scheduleEnabled", enabled).apply();
    }

    /** 每天几点跑（0-23） */
    public int getScheduleHour() {
        return prefs.getInt("scheduleHour", 8);
    }

    public void setScheduleHour(int hour) {
        prefs.edit().putInt("scheduleHour", Math.max(0, Math.min(23, hour))).apply();
    }

    public boolean isFetchDetail() {
        return prefs.getBoolean("fetchDetail", true);
    }

    public void setFetchDetail(boolean enabled) {
        prefs.edit().putBoolean("fetchDetail", enabled).apply();
        settings.detail.enabled = enabled;
    }

    public boolean isVerifyLink() {
        return prefs.getBoolean("verifyLink", true);
    }

    public void setVerifyLink(boolean enabled) {
        prefs.edit().putBoolean("verifyLink", enabled).apply();
        settings.verify.enabled = enabled;
    }

    // ------------------------------------------------------ 截止日期提醒

    /** 截止提醒总开关（默认开：这是"别错过能投的项目"的最后一道保险）。 */
    public boolean isReminderEnabled() {
        return prefs.getBoolean("reminderEnabled", true);
    }
    public void setReminderEnabled(boolean enabled) {
        prefs.edit().putBoolean("reminderEnabled", enabled).apply();
    }

    /** 提前几天开始提醒（1 / 3 / 7）。 */
    public int getReminderDays() {
        return prefs.getInt("reminderDays", 3);
    }

    public void setReminderDays(int days) {
        int clamped = Math.max(1, Math.min(30, days));
        prefs.edit().putInt("reminderDays", clamped).apply();
    }

    // ------------------------------------------------------ 数据保留

    /**
     * 已截止的公告保留多少天后可以清理（默认 180 天）。
     *
     * <p>只作为"清理"按钮的默认阈值，<b>不会自动删除任何东西</b> ——
     * 公告删了就得重新采集，而下架过的可能再也抓不到，所以清理必须是用户主动触发。
     */
    public int getRetentionDays() {
        return prefs.getInt("retentionDays", 180);
    }

    public void setRetentionDays(int days) {
        prefs.edit().putInt("retentionDays", Math.max(1, Math.min(3650, days))).apply();
    }

    // ------------------------------------------------------ 上次采集结果快照

    /**
     * 采集结束后把汇总结果落一份快照。
     *
     * <p><b>为什么必须落盘</b>：汇总只活在进程内存里（{@code CrawlEngine.getLastSummary}）。
     * 应用一旦被系统回收或手动重启，「采集」页就退回"尚未执行采集"，
     * 而「日志」页明明有历史记录、统计也还在 —— 两页自相矛盾，看着像数据丢了。
     *
     * <p>为什么不能直接从数据库日志反推：{@code crawl_log} 是<b>按站点</b>存的
     * （一次采集 3 条），没有"这几条属于同一次采集"的分组信息，
     * 拼不出那个"新增 28 条 / 耗时 107 秒"的汇总数字，只能单独存一份快照。
     */
    public void saveLastSummary(CrawlSummary summary) {
        if (summary == null) {
            return;
        }
        prefs.edit()
                .putString("lastSummaryStatus", summary.status == null ? "" : summary.status)
                .putString("lastSummaryMessage", summary.message == null ? "" : summary.message)
                .putString("lastSummaryTrigger", summary.triggerType == null ? "" : summary.triggerType)
                .putString("lastSummaryFinishedAt",
                        summary.finishedAt == null ? "" : summary.finishedAt.toString())
                .putInt("lastSummaryCandidate", summary.candidateCount)
                .putInt("lastSummaryExcluded", summary.excludedCount)
                .putInt("lastSummaryMatched", summary.matchedCount)
                .putInt("lastSummarySaved", summary.savedCount)
                .putInt("lastSummaryDuplicate", summary.duplicateCount)
                .putInt("lastSummaryFailed", summary.failedCount)
                .putInt("lastSummaryVerified", summary.verifiedCount)
                .putLong("lastSummaryRequests", summary.requestCount)
                .putLong("lastSummaryDuration", summary.durationMs)
                .apply();
    }

    /** 读回上次采集快照；从没采集过时返回 null，界面就照常显示"尚未执行采集"。 */
    public CrawlSummary loadLastSummary() {
        String status = prefs.getString("lastSummaryStatus", "");
        if (status == null || status.isEmpty()) {
            return null;
        }
        CrawlSummary summary = new CrawlSummary();
        summary.status = status;
        summary.message = prefs.getString("lastSummaryMessage", "");
        summary.triggerType = prefs.getString("lastSummaryTrigger", "");
        summary.finishedAt = parseStoredTime(prefs.getString("lastSummaryFinishedAt", ""));
        summary.candidateCount = prefs.getInt("lastSummaryCandidate", 0);
        summary.excludedCount = prefs.getInt("lastSummaryExcluded", 0);
        summary.matchedCount = prefs.getInt("lastSummaryMatched", 0);
        summary.savedCount = prefs.getInt("lastSummarySaved", 0);
        summary.duplicateCount = prefs.getInt("lastSummaryDuplicate", 0);
        summary.failedCount = prefs.getInt("lastSummaryFailed", 0);
        summary.verifiedCount = prefs.getInt("lastSummaryVerified", 0);
        summary.requestCount = prefs.getLong("lastSummaryRequests", 0L);
        summary.durationMs = prefs.getLong("lastSummaryDuration", 0L);
        return summary;
    }

    private static java.time.LocalDateTime parseStoredTime(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return java.time.LocalDateTime.parse(value);
        } catch (Exception e) {
            // 存的是我们自己写的 ISO 串，解析不了就当没有，不影响其它字段
            return null;
        }
    }

    public List<String> siteCodes() {
        return Arrays.asList(AqggzyCrawler.SITE_CODE, CgjCrawler.SITE_CODE, WyxmzxCrawler.SITE_CODE);
    }
}

