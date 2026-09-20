package com.wanhong.tender.crawl;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.wanhong.crawler.pipeline.DeadlineReminder;
import com.wanhong.crawler.pipeline.TenderRecord;
import com.wanhong.tender.R;
import com.wanhong.tender.ui.MainActivity;

import java.util.List;

/**
 * 截止日期提醒通知。
 *
 * <p>刻意用**独立通知渠道**（而不是复用采集进度那个）：采集进度属于"过程噪声"，
 * 用户很可能想静音；截止提醒是"别错过能投的项目"，必须留下。
 * 合在一个渠道里的话，用户一静音就把重要提醒也静掉了。
 */
public final class ReminderNotifier {

    public static final String CHANNEL_ID = "deadline_reminder";
    private static final int NOTIFICATION_ID = 1003;
    /** requestCode 要和「采集完成」通知错开：PendingIntent 的比较忽略 extras，
     *  同一个 requestCode 会让两个通知互相覆盖对方的 extras */
    private static final int REQUEST_CODE = 1003;

    private ReminderNotifier() {
    }

    /** 建渠道。放在 Application 启动时调用，避免通知发出去时才建导致第一条被丢弃。 */
    public static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                context.getString(R.string.reminder_channel_name), NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(context.getString(R.string.reminder_channel_desc));
        manager.createNotificationChannel(channel);
    }

    /**
     * 发一条汇总通知。
     *
     * <p>用固定 ID，所以每天只会在通知栏留最新的一条，不会越堆越多。
     */
    public static void notifyDue(Context context, List<TenderRecord> due) {
        if (due == null || due.isEmpty()) {
            return;
        }
        ensureChannel(context);
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }

        String title = DeadlineReminder.buildTitle(due);
        String message = DeadlineReminder.buildMessage(due);

        // 点通知直接回到 App：默认视图就是"未截止 + 按截止排序 + 只看可投"，
        // 正好是用户此刻最需要的那一屏。
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(context, REQUEST_CODE, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(message.split("\n")[0])
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .setContentIntent(pending)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        manager.notify(NOTIFICATION_ID, builder.build());
    }
}
