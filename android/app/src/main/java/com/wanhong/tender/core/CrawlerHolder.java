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

        EpointSearchClient searchClient = new EpointSearchClient(fetcher, new ObjectMapper(),
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
                detailParser, deadlineExtractor, linkVerifier, new ObjectMapper(),
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

    public List<String> siteCodes() {
        return Arrays.asList(AqggzyCrawler.SITE_CODE, CgjCrawler.SITE_CODE, WyxmzxCrawler.SITE_CODE);
    }
}
