package com.wanhong.tender;

import android.app.Application;
import android.os.Build;
import android.util.Log;

import com.wanhong.tender.core.CrawlerHolder;
import com.wanhong.tender.crawl.CrawlScheduler;
import com.wanhong.tender.crawl.CrawlWorker;
import com.wanhong.tender.server.LocalApiServer;

/**
 * 应用入口。
 *
 * <p>启动时做三件事：
 * <ol>
 *   <li>把内核（爬虫/存储）准备好；</li>
 *   <li>起本地 HTTP 服务，给 WebView 提供页面与接口；</li>
 *   <li>注册定时采集（WorkManager 会在设备重启后自动恢复）。</li>
 * </ol>
 */
public class TenderApp extends Application {

    private static final String TAG = "TenderApp";

    private LocalApiServer server;

    @Override
    public void onCreate() {
        super.onCreate();

        // 统一时区：截止日期与"是否过期"都基于它
        CrawlerHolder holder = CrawlerHolder.get(this);
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(holder.settings.timezone));

        CrawlWorker.ensureChannel(this);

        try {
            server = new LocalApiServer(this);
            server.start(3000, true);
            Log.i(TAG, "本地服务已启动：http://127.0.0.1:" + LocalApiServer.PORT);
        } catch (Exception e) {
            Log.e(TAG, "本地服务启动失败", e);
        }

        CrawlScheduler.schedule(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Log.i(TAG, "Android 13+ 需要用户授权通知权限，才能看到采集进度与结果提示");
        }
    }

    public LocalApiServer getServer() {
        return server;
    }

    @Override
    public void onTerminate() {
        if (server != null) {
            server.stop();
        }
        super.onTerminate();
    }
}
