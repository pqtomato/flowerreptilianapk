package com.wanhong.tender.crawl;

import android.content.Context;
import android.util.Log;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.wanhong.tender.core.CrawlerHolder;

import java.util.concurrent.TimeUnit;

/**
 * 截止提醒的每日调度。
 *
 * <p>时间上**排在采集之后半小时**：这样提醒看到的是当天刚采完的最新数据，
 * 而不是昨天的库。所以这里不额外给用户一个"提醒时间"的下拉 —— 少一个需要理解的设置项，
 * 行为也更符合直觉（先采集，再提醒）。
 */
public final class ReminderScheduler {

    private static final String TAG = "ReminderScheduler";
    private static final String PERIODIC_WORK = "flower-tender-reminder";

    /** 采集时间之后多久开始检查 */
    private static final long OFFSET_MINUTES = 30L;

    private ReminderScheduler() {
    }

    public static void schedule(Context context) {
        CrawlerHolder holder = CrawlerHolder.get(context);
        if (!holder.isReminderEnabled()) {
            cancel(context);
            return;
        }

        long delayMinutes = CrawlScheduler.delayToNextRun(holder.getScheduleHour()) + OFFSET_MINUTES;
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(ReminderWorker.class, 1, TimeUnit.DAYS)
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .addTag(PERIODIC_WORK)
                .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE, request);
        Log.i(TAG, "截止提醒已排期：每天 " + holder.getScheduleHour() + ":00 采集之后 "
                + OFFSET_MINUTES + " 分钟，首次约 " + (delayMinutes / 60) + " 小时后"
                + "（提前 " + holder.getReminderDays() + " 天口径）");
    }

    public static void cancel(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK);
    }
}
