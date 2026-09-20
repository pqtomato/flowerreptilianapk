package com.wanhong.tender.crawl;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.wanhong.tender.R;
import com.wanhong.tender.core.CrawlerHolder;
import com.wanhong.tender.pipeline.CrawlSummaryBridge;
import com.wanhong.tender.ui.MainActivity;

/**
 * 后台采集任务。
 *
 * <p>为什么要以前台服务方式跑：一次全量采集要几分钟，普通后台任务很容易被系统掐掉。
 * 用 {@link ForegroundInfo} 挂一条常驻通知，既符合 Android 的限制，用户也能看到进度。
 */
public class CrawlWorker extends Worker {

    public static final String CHANNEL_ID = "crawl_task";
    private static final int NOTIFICATION_ID = 1001;

    public static final String KEY_SITE = "site";
    public static final String KEY_MAX_KEYWORDS = "maxKeywords";
    public static final String KEY_LOOKBACK_DAYS = "lookbackDays";

    public CrawlWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        CrawlerHolder holder = CrawlerHolder.get(context);

        String site = getInputData().getString(KEY_SITE);
        if (site == null || site.isEmpty()) {
            site = "all";
        }
        int maxKeywords = getInputData().getInt(KEY_MAX_KEYWORDS, 0);
        int lookbackDays = getInputData().getInt(KEY_LOOKBACK_DAYS, -1);

        setForegroundAsync(foregroundInfo(context.getString(R.string.crawl_running)));

        try {
            CrawlSummaryBridge.Summary summary = CrawlSummaryBridge.run(holder, site,
                    maxKeywords > 0 ? maxKeywords : null,
                    lookbackDays >= 0 ? lookbackDays : null,
                    "SCHEDULED",
                    message -> setForegroundAsync(foregroundInfo(message)));
            CrawlNotifier.notifyResult(context, summary);
            return "FAILED".equals(summary.status) ? Result.retry() : Result.success();
        } catch (Exception e) {
            CrawlNotifier.notifyError(context, e.getMessage());
            return Result.failure();
        }
    }

    private ForegroundInfo foregroundInfo(String text) {
        Context context = getApplicationContext();
        ensureChannel(context);
        Intent intent = new Intent(context, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pending)
                .setPriority(NotificationCompat.PRIORITY_LOW);
        return new ForegroundInfo(NOTIFICATION_ID, builder.build());
    }

    public static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.crawl_channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(context.getString(R.string.crawl_channel_desc));
        manager.createNotificationChannel(channel);
    }
}

