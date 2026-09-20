package com.wanhong.tender.crawl;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.wanhong.tender.core.CrawlerHolder;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

/**
 * 定时采集调度。
 *
 * <p>用 WorkManager 而不是 AlarmManager：
 * <ul>
 *   <li>系统会在设备重启后自动恢复，不用自己写开机广播；</li>
 *   <li>能声明"需要网络""电量不低"等约束，省电且规范；</li>
 *   <li>配合 {@code setForeground} 可以跑几分钟的长任务。</li>
 * </ul>
 *
 * <p>代价是<b>时间不精确</b>：Doze / 省电模式会把任务推迟，国产 ROM 若不允许自启动还会直接不跑。
 * 所以应用内提供了"立即采集"以及引导用户去加白名单的说明。
 */
public final class CrawlScheduler {

    private static final String PERIODIC_WORK = "flower-tender-daily";
    private static final String MANUAL_WORK = "flower-tender-manual";

    private CrawlScheduler() {
    }

    /** 按用户设定的时间，每天跑一次。 */
    public static void schedule(Context context) {
        CrawlerHolder holder = CrawlerHolder.get(context);
        if (!holder.isScheduleEnabled()) {
            cancel(context);
            return;
        }

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build();

        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(CrawlWorker.class, 1, TimeUnit.DAYS)
                .setInitialDelay(delayToNextRun(holder.getScheduleHour()), TimeUnit.MINUTES)
                .setConstraints(constraints)
                .addTag(PERIODIC_WORK)
                .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE, request);
    }

    public static void cancel(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK);
    }

    /** 立即采集一次（页面上的按钮）。 */
    public static void runNow(Context context, String site, Integer maxKeywords, Integer lookbackDays) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(CrawlWorker.class)
                .setConstraints(constraints)
                .setInputData(new androidx.work.Data.Builder()
                        .putString(CrawlWorker.KEY_SITE, site == null ? "all" : site)
                        .putInt(CrawlWorker.KEY_MAX_KEYWORDS, maxKeywords == null ? 0 : maxKeywords)
                        .putInt(CrawlWorker.KEY_LOOKBACK_DAYS, lookbackDays == null ? -1 : lookbackDays)
                        .build())
                .addTag(MANUAL_WORK)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(MANUAL_WORK,
                ExistingWorkPolicy.REPLACE, request);
    }

    /** 计算距离下一次整点运行的分钟数（保持"每天 8 点"这种直觉）。提醒任务也复用它。 */
    static long delayToNextRun(int hour) {
        Calendar now = Calendar.getInstance();
        Calendar target = Calendar.getInstance();
        target.set(Calendar.HOUR_OF_DAY, hour);
        target.set(Calendar.MINUTE, 0);
        target.set(Calendar.SECOND, 0);
        target.set(Calendar.MILLISECOND, 0);
        if (!target.after(now)) {
            target.add(Calendar.DAY_OF_YEAR, 1);
        }
        long minutes = (target.getTimeInMillis() - now.getTimeInMillis()) / 60000L;
        // WorkManager 的 initialDelay 至少要 1 分钟
        return Math.max(1L, minutes);
    }
}
